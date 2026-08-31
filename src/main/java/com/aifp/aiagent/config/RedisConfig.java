package com.aifp.aiagent.config;

import com.aifp.aiagent.task.ProgressMessageListener;
import com.aifp.aiagent.task.ProgressPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 * <p>
 * 提供：① {@link RedisTemplate}（String key + JSON value，保留类型信息），
 * ② 值序列化器 bean（供 {@link ProgressMessageListener} 对称反序列化 Pub/Sub 消息），
 * ③ {@link RedisMessageListenerContainer} 订阅 {@link ProgressPublisher#CHANNEL}。
 *
 * @author Tang_tzb
 */
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private final RedisConnectionFactory connectionFactory;
    private final ProgressMessageListener progressMessageListener;

    /**
     * 值序列化器：JSON + 默认类型信息，发布端写入 {@code @class}，
     * 订阅端可据此还原为 {@link com.aifp.aiagent.dto.TaskProgress} 等具体类型。
     * <p>
     * 声明为 {@code static}：Spring 直接通过类调用，无需先实例化 {@link RedisConfig}，
     * 从而打破 RedisConfig ⇄ ProgressMessageListener ⇄ RedisSerializer 的循环依赖
     * （ProgressMessageListener 构造注入此 bean，而 RedisConfig 又注入 ProgressMessageListener）。
     */
    @Bean
    public static RedisSerializer<Object> redisValueSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisSerializer<Object> redisValueSerializer) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(redisValueSerializer);
        template.setHashValueSerializer(redisValueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Pub/Sub 容器：订阅 {@link ProgressPublisher#CHANNEL}，消息交由
     * {@link ProgressMessageListener} 按 taskId 路由到 SSE。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        Topic topic = new ChannelTopic(ProgressPublisher.CHANNEL);
        container.addMessageListener(progressMessageListener, topic);
        return container;
    }
}
