package com.astra.freyja.service.video;

import com.astra.freyja.service.video.VideoBatchModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/** Persistent queue. Every mutation is called inside the service's transaction boundary. */
@Repository
@RequiredArgsConstructor
public class VideoBatchStore {
    private final JdbcTemplate jdbc;

    public void lockQueue() { jdbc.queryForObject("SELECT id FROM video_render_dispatch_state WHERE id=1 FOR UPDATE", Long.class); }
    public String currentTask() { return jdbc.queryForObject("SELECT task_id FROM video_render_dispatch_state WHERE id=1", String.class); }
    public void claim(String taskId) { jdbc.update("UPDATE video_render_dispatch_state SET task_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=1", taskId); }
    public void release(String taskId) { jdbc.update("UPDATE video_render_dispatch_state SET task_id=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=1 AND task_id=?", taskId); }
    public void holdShot(Long shot, String task) { jdbc.update("INSERT INTO shot_video_render_slot(shot_id,task_id) VALUES (?,?)", shot, task); }
    public boolean occupied(Long shot) { return jdbc.queryForObject("SELECT COUNT(*) FROM shot_video_render_slot WHERE shot_id=?", Long.class, shot) > 0; }
    public void releaseShot(String task) { jdbc.update("DELETE FROM shot_video_render_slot WHERE task_id=?", task); }

    public void insert(Batch b) {
        jdbc.update("INSERT INTO video_render_batch(id,drama_id,name,request_id,request_hash,options_json,status,cancel_requested,created_at) VALUES (?,?,?,?,?,?,'QUEUED',0,?)",
                b.getId(), b.getDramaId(), b.getName(), b.getRequestId(), b.getRequestHash(), b.getOptionsJson(), b.getCreatedAt());
    }
    public Batch byRequest(String request) { return first(jdbc.query("SELECT * FROM video_render_batch WHERE request_id=?", BATCH, request)); }
    public Batch batch(Long id) { return first(jdbc.query("SELECT * FROM video_render_batch WHERE id=?", BATCH, id)); }
    public List<Batch> batches(boolean active, int limit) {
        return jdbc.query("SELECT * FROM video_render_batch WHERE status " + (active ? "IN" : "NOT IN")
                + " ('QUEUED','RUNNING','CANCELLING','NEEDS_REVIEW') ORDER BY created_at DESC,id DESC LIMIT ?", BATCH, limit);
    }
    public void batchStatus(Long id, String status, boolean finished) {
        jdbc.update("UPDATE video_render_batch SET status=?,finished_at=?,revision=revision+1 WHERE id=?", status, finished ? LocalDateTime.now() : null, id);
    }
    public void requestCancel(Long id) { jdbc.update("UPDATE video_render_batch SET cancel_requested=1,status='CANCELLING',revision=revision+1 WHERE id=?", id); }
    public void insert(Item i) {
        jdbc.update("INSERT INTO video_render_batch_item(id,batch_id,queue_order,shot_id,task_id,name,phase,execution_order,status,snapshot_json,error_message,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                i.getId(), i.getBatchId(), i.getQueueOrder(), i.getShotId(), i.getTaskId(), i.getName(), i.getPhase(), i.getExecutionOrder(), i.getStatus(), i.getSnapshotJson(), i.getErrorMessage(), i.getCreatedAt());
    }
    public List<Item> items(Long batch) { return jdbc.query("SELECT * FROM video_render_batch_item WHERE batch_id=? ORDER BY execution_order,id", ITEM, batch); }
    public Item task(String task) { return first(jdbc.query("SELECT * FROM video_render_batch_item WHERE task_id=?", ITEM, task)); }
    public Item next() {
        return first(jdbc.query("SELECT i.* FROM video_render_batch_item i LEFT JOIN video_render_batch b ON b.id=i.batch_id WHERE i.status='QUEUED' AND (b.id IS NULL OR b.cancel_requested=0) ORDER BY i.queue_order,i.execution_order,i.id LIMIT 1", ITEM));
    }
    public void status(String task, String status, String error, String output, String upstream) {
        jdbc.update("UPDATE video_render_batch_item SET status=?,error_message=?,output_url=COALESCE(?,output_url),upstream_status=COALESCE(?,upstream_status),finished_at=? WHERE task_id=? AND status NOT IN ('SUCCESS','FAILED','CANCELLED','SKIPPED')",
                status, error, output, upstream, terminal(status) ? LocalDateTime.now() : null, task);
    }
    public void resolved(String task, String json) { jdbc.update("UPDATE video_render_batch_item SET resolved_json=? WHERE task_id=?", json, task); }
    public int progress(String task,String stage,String message){
        return jdbc.update("UPDATE video_render_batch_item SET execution_stage=?,stage_message=?,stage_updated_at=CURRENT_TIMESTAMP WHERE task_id=? AND status IN ('DISPATCHING','RENDERING')",stage,message,task);
    }
    public void cancelEvidence(String task, String upstream) { jdbc.update("UPDATE video_render_batch_item SET upstream_status=? WHERE task_id=?", upstream, task); }
    public void cancelUnconfirmedFailure(String task) {
        jdbc.update("UPDATE video_render_batch_item SET status='CANCELLED',error_message='用户取消整个任务',finished_at=CURRENT_TIMESTAMP WHERE task_id=? AND status='FAILED' AND upstream_status='UNCONFIRMED'", task);
    }
    public List<Item> pendingReview() { return jdbc.query("SELECT * FROM video_render_batch_item WHERE status='NEEDS_REVIEW'", ITEM); }
    public void confirmRemoteFailure(String task, String status) {
        jdbc.update("UPDATE video_render_batch_item SET status='FAILED',upstream_status='CONFIRMED',error_message=?,finished_at=CURRENT_TIMESTAMP WHERE task_id=? AND status IN ('FAILED','NEEDS_REVIEW')", "上游已明确结束："+status, task);
    }
    public void recovery(String task,String phase,String url){jdbc.update("UPDATE video_render_batch_item SET failure_phase=?,recovery_url=? WHERE task_id=?",phase,url,task);}
    public void retryArchive(String task){jdbc.update("UPDATE video_render_batch_item SET status='QUEUED',finished_at=NULL WHERE task_id=? AND status='FAILED'",task);}
    public void recoverCompleted(String task, String url) {
        jdbc.update("UPDATE video_render_batch_item SET status='QUEUED',failure_phase='ARCHIVE',recovery_url=?,upstream_status='CONFIRMED',error_message=NULL,finished_at=NULL WHERE task_id=? AND status IN ('FAILED','NEEDS_REVIEW')", url, task);
    }
    public static boolean terminal(String s) { return List.of("SUCCESS","FAILED","CANCELLED","SKIPPED").contains(s); }
    private static <T> T first(List<T> list) { return list.isEmpty() ? null : list.getFirst(); }
    private static LocalDateTime time(ResultSet rs, String col) throws SQLException { var v=rs.getTimestamp(col); return v==null?null:v.toLocalDateTime(); }
    private static Long number(ResultSet rs, String col) throws SQLException { long n=rs.getLong(col); return rs.wasNull()?null:n; }
    private static final RowMapper<Batch> BATCH = (rs,n) -> {
        Batch b=new Batch(); b.setId(rs.getLong("id")); b.setDramaId(rs.getLong("drama_id")); b.setName(rs.getString("name"));
        b.setRequestId(rs.getString("request_id")); b.setRequestHash(rs.getString("request_hash")); b.setOptionsJson(rs.getString("options_json"));
        b.setStatus(rs.getString("status"));b.setRevision(rs.getInt("revision")); b.setCancelRequested(rs.getBoolean("cancel_requested")); b.setCreatedAt(time(rs,"created_at")); b.setFinishedAt(time(rs,"finished_at")); return b;
    };
    private static final RowMapper<Item> ITEM = (rs,n) -> {
        Item i=new Item(); i.setId(rs.getLong("id")); i.setBatchId(number(rs,"batch_id")); i.setQueueOrder(rs.getLong("queue_order"));
        i.setShotId(rs.getLong("shot_id")); i.setTaskId(rs.getString("task_id")); i.setName(rs.getString("name")); i.setPhase(rs.getString("phase"));
        i.setExecutionOrder(rs.getInt("execution_order")); i.setStatus(rs.getString("status")); i.setSnapshotJson(rs.getString("snapshot_json"));
        i.setResolvedJson(rs.getString("resolved_json")); i.setErrorMessage(rs.getString("error_message")); i.setOutputUrl(rs.getString("output_url"));
        i.setUpstreamStatus(rs.getString("upstream_status"));i.setFailurePhase(rs.getString("failure_phase"));i.setRecoveryUrl(rs.getString("recovery_url"));
        i.setExecutionStage(rs.getString("execution_stage"));i.setStageMessage(rs.getString("stage_message"));i.setStageUpdatedAt(time(rs,"stage_updated_at"));
        i.setCreatedAt(time(rs,"created_at")); i.setFinishedAt(time(rs,"finished_at")); return i;
    };
}
