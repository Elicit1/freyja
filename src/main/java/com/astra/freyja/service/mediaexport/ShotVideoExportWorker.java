package com.astra.freyja.service.mediaexport;

import com.astra.freyja.config.VideoExportProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.astra.freyja.service.mediaexport.VideoExportModels.*;

@Component
@Slf4j
public class ShotVideoExportWorker implements MessageListener, SubscriptionListener {
    public static final String CHANNEL="freyja:video-export:changed";
    private final DataSource dataSource;
    private final ShotVideoExportService service;
    private final VideoExportProperties properties;
    private final Executor executor;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final String origin=UUID.randomUUID().toString();
    private final AtomicBoolean requested=new AtomicBoolean(),scheduled=new AtomicBoolean();
    public ShotVideoExportWorker(DataSource dataSource,ShotVideoExportService service,VideoExportProperties properties,
                                 @Qualifier("videoExportExecutor") Executor executor,StringRedisTemplate redis,ApplicationEventPublisher events){this.dataSource=dataSource;this.service=service;this.properties=properties;this.executor=executor;this.redis=redis;this.events=events;}
    @EventListener(ApplicationReadyEvent.class)
    public void ready(){wake();}
    @TransactionalEventListener(fallbackExecution=true)
    public void changed(QueueChanged event){
        service.signalCancellation();wake();
        if(!event.remote())try{redis.convertAndSend(CHANNEL,origin);}catch(Exception e){log.warn("视频打包跨实例通知失败: {}",e.getMessage());}
    }
    @Override public void onMessage(Message message,byte[] pattern){if(!origin.equals(new String(message.getBody(),StandardCharsets.UTF_8)))events.publishEvent(new QueueChanged(true));}
    @Override public void onChannelSubscribed(byte[] channel,long count){service.signalCancellation();wake();}
    private void wake(){
        if(!properties.isEnabled())return;requested.set(true);
        if(!scheduled.compareAndSet(false,true))return;
        executor.execute(()->{
            try{
                while(requested.getAndSet(false))consume();
            }finally{scheduled.set(false);if(requested.get())wake();}
        });
    }
    private void consume(){
        try(var connection=dataSource.getConnection()){
            String lock="freyja-video-export-"+ShotVideoExportSelection.hash(String.valueOf(connection.getCatalog())).substring(0,20);
            try(var statement=connection.prepareStatement("SELECT GET_LOCK(?,86400)")){statement.setString(1,lock);try(var result=statement.executeQuery()){if(!result.next()||result.getInt(1)!=1)throw new IllegalStateException("无法取得视频打包调度锁");}}
            try{service.recover();while(service.runNext()){ /* completion advances the queue, no timer */ }}
            finally{try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){statement.setString(1,lock);statement.execute();}}
        }catch(Exception e){log.warn("视频打包队列执行失败，请确认 shot-video-export.sql 已执行",e);}
    }
}
