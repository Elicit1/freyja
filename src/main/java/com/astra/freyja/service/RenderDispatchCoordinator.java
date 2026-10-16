package com.astra.freyja.service;

import com.astra.freyja.common.BizException;
import com.astra.freyja.event.RenderQueueChangedEvent;
import com.astra.freyja.service.video.VideoBatchPreparation;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Shared admission for images, post-processing and the persistent video consumer. */
@Service
@RequiredArgsConstructor
public class RenderDispatchCoordinator {
    private final DataSource dataSource;
    private final ReentrantLock execution = new ReentrantLock(true);
    private final Object changed = new Object();
    private long revision;

    @TransactionalEventListener(fallbackExecution = true)
    public void queueChanged(RenderQueueChangedEvent event) {
        synchronized (changed) { revision++; changed.notifyAll(); }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void dispatchReleased(com.astra.freyja.event.RenderDispatchReleasedEvent event) {
        queueChanged(new RenderQueueChangedEvent());
    }

    public <T> T executeSingle(Supplier<T> action) {
        LocalDateTime submittedAt = LocalDateTime.now();
        for (;;) {
            long observed;
            synchronized (changed) { observed = revision; }
            try {
                execution.lockInterruptibly();
                try (Connection connection = dataSource.getConnection()) {
                    String name = acquire(connection);
                    try {
                        if (!videoWorkBefore(connection, submittedAt)) return action.get();
                    } finally { release(connection, name); }
                } finally { execution.unlock(); }
                // Wait for a real queue change, without a timer or repeated status scan.
                synchronized (changed) { while (revision == observed) changed.wait(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("渲染排队已取消");
            } catch (SQLException e) {
                throw new BizException(500, "无法获取全局渲染调度锁: " + e.getMessage());
            }
        }
    }

    public void executeVideoQueue(Runnable action) {
        try {
            execution.lockInterruptibly();
            try (Connection connection = dataSource.getConnection()) {
                String name = acquire(connection);
                try { action.run(); }
                finally { release(connection, name); }
            } finally { execution.unlock(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("视频队列调度已中断");
        } catch (SQLException e) {
            throw new BizException(500, "无法获取全局渲染调度锁: " + e.getMessage());
        }
    }

    private boolean videoWorkBefore(Connection connection, LocalDateTime submittedAt) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT task_id FROM video_render_dispatch_state WHERE id=1")) {
            try (var result = statement.executeQuery()) {
                if (result.next() && result.getString(1) != null) return true;
            }
        }
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM video_render_batch_item i LEFT JOIN video_render_batch b ON b.id=i.batch_id WHERE i.status='QUEUED' AND (b.id IS NULL OR b.cancel_requested=0) AND i.created_at<=?")) {
            statement.setObject(1, submittedAt);
            try (var result = statement.executeQuery()) { return result.next() && result.getLong(1) > 0; }
        }
    }

    private String acquire(Connection connection) throws SQLException {
        String name = "freyja-video-" + VideoBatchPreparation.hash(String.valueOf(connection.getCatalog())).substring(0, 20);
        // MySQL blocks until the owning session releases; no lock polling.
        try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,86400)")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) throw new SQLException("等待渲染调度锁失败");
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            release(connection, name);
            throw new CancellationException("渲染排队已取消");
        }
        return name;
    }

    private void release(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, name);
            statement.execute();
        }
    }
}
