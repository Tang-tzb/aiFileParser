package com.aifp.aiagent.config;

import com.aifp.aiagent.task.ProgressMessageListener;
import com.aifp.aiagent.task.ProgressPublisher;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
     * 自定义 ObjectMapper（阶段 13 缺陷修复）：无参构造器的内置 mapper 不注册 JSR310 模块，
     * {@code Map<String,Object>} 中的 {@link java.time.LocalDate}（如 FieldType.DATE 字段值）
     * 会因多态 type id 无序列化器而抛 UnsupportedTypeSerializer；
     * 此处注册 JavaTimeModule 并输出 ISO-8601 日期字符串，NON_FINAL 类型化保证
     * TaskProgress/result/values 全链路 round-trip 无损，PTV 白名单约束多态反序列化范围。
     * <p>
     * 声明为 {@code static}：Spring 直接通过类调用，无需先实例化 {@link RedisConfig}，
     * 从而打破 RedisConfig ⇄ ProgressMessageListener ⇄ RedisSerializer 的循环依赖
     * （ProgressMessageListener 构造注入此 bean，而 RedisConfig 又注入 ProgressMessageListener）。
     */
    @Bean
    public static RedisSerializer<Object> redisValueSerializer() {
        return new GenericJackson2JsonRedisSerializer(buildValueObjectMapper());
    }

    /**
     * 进度链路专用 ObjectMapper：JSR310 类型（LocalDate/LocalDateTime）序列化为 ISO-8601 字符串，
     * 并保留 {@code @class} 类型信息（NON_FINAL + As.PROPERTY）保证订阅端对称反序列化。
     */
    private static ObjectMapper buildValueObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .allowIfSubType("com.aifp.aiagent.")
                        .allowIfSubType("java.")
                        .allowIfSubType("org.springframework.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
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
