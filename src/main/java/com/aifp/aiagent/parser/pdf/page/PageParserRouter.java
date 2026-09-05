package com.aifp.aiagent.parser.pdf.page;

import com.aifp.aiagent.common.ResultCode;
import com.aifp.aiagent.exception.BusinessException;
import com.aifp.aiagent.parser.pdf.PageContentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 页面解析器路由器：注册表模式，无 if/else 分支链（阶段 2 验收硬性要求）。
 * <p>
 * 与 {@code FileParserRegistry} 同构：Spring 注入全部 {@link PageParser}，
 * 按 {@code supportedType()} 建立 Map，路由为 O(1) 查表；
 * 新增解析器 = 新增 @Component，路由零改动（开闭原则）。
 *
 * @author Tang_tzb
 */
@Slf4j
@Component
public class PageParserRouter {

    /**
     * 类型 → 解析器注册表
     */
    private final Map<PageContentType, PageParser> parsers;

    public PageParserRouter(List<PageParser> parserList) {
        this.parsers = parserList.stream()
                .collect(Collectors.toMap(
                        PageParser::supportedType,
                        Function.identity(),
                        (a, b) -> a,
                        () -> new EnumMap<>(PageContentType.class)));
        log.info("页面解析器注册完成: {}", parsers.keySet());
    }

    /**
     * 按页面画像的内容类型路由解析器。
     */
    public PageParser route(PageContext context) {
        PageContentType type = context.getProfile().getContentType();
        PageParser parser = parsers.get(type);
        if (parser == null) {
            // 防御分支：四种类型均已内置注册，仅扩展期未注册时可达
            throw new BusinessException(ResultCode.FILE_PARSE_ERROR,
                    "未注册的页面内容类型解析器: " + type);
        }
        return parser;
    }
}
