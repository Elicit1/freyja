package com.astra.freyja.service;

import com.astra.freyja.config.VideoExportProperties;
import com.astra.freyja.service.mediaexport.ShotVideoExportService;
import com.astra.freyja.service.mediaexport.ShotVideoExportWorker;
import com.astra.freyja.service.mediaexport.VideoExportModels.QueueChanged;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ShotVideoExportWorkerTest {
    @Test void redisResubscriptionWakesSleepingQueueWithoutATimer(){
        var ds=mock(DataSource.class);var service=mock(ShotVideoExportService.class);List<Runnable> submitted=new ArrayList<>();
        var worker=new ShotVideoExportWorker(ds,service,new VideoExportProperties(),submitted::add,mock(StringRedisTemplate.class),mock(ApplicationEventPublisher.class));
        worker.onChannelSubscribed(new byte[0],1);worker.onChannelSubscribed(new byte[0],1);
        assertEquals(1,submitted.size());verifyNoInteractions(ds);
    }
    @Test void queueIsIdleUntilAnEventThenConsumesToCompletionWithoutTimer()throws Exception{
        DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);when(connection.getCatalog()).thenReturn("freyja");PreparedStatement statement=mock(PreparedStatement.class);when(connection.prepareStatement(anyString())).thenReturn(statement);ResultSet result=mock(ResultSet.class);when(statement.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);when(result.getInt(1)).thenReturn(1);
        var service=mock(ShotVideoExportService.class);when(service.runNext()).thenReturn(true,true,false);var redis=mock(StringRedisTemplate.class);List<Runnable> submitted=new ArrayList<>();var worker=new ShotVideoExportWorker(ds,service,new VideoExportProperties(),submitted::add,redis,mock(ApplicationEventPublisher.class));
        verifyNoInteractions(service);worker.changed(new QueueChanged());assertEquals(1,submitted.size());verify(service).signalCancellation();verify(service,never()).runNext();submitted.removeFirst().run();verify(service).recover();verify(service,times(3)).runNext();assertTrue(submitted.isEmpty());verify(redis).convertAndSend(eq(ShotVideoExportWorker.CHANNEL),anyString());
    }
}
