package com.aifp.aiagent.service.impl;

import com.aifp.aiagent.service.ProjectAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 项目访问权限默认实现：全放行
 * <p>
 * 当前系统无用户体系，所有项目对当前使用者可见。
 * 后续接入认证体系后，替换为基于 userId 的访问控制实现
 * （本类仅作占位，届时新增实现类并标注 @Primary 或移除本类）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class DefaultProjectAccessService implements ProjectAccessService {

    @Override
    public boolean canAccess(Long projectId) {
        // 无用户体系阶段：恒放行；权限拒绝统一由调用方转为 FORBIDDEN 业务响应
        return true;
    }
}
