package com.hmdp.ai.prompt;

import com.hmdp.ai.model.AgentIntent;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiPromptService {

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是探店笔记平台的AI助手，帮助用户推荐店铺、查询优惠券、回答美食和本地生活相关问题。"
                    + "语气轻松友好，回答简洁自然，尽量控制在100字以内。";

    private static final String DEFAULT_AGENT_SYSTEM_PROMPT =
            "你是探店笔记平台的AI助手。用户询问店铺推荐、优惠券、评分、距离等信息时，"
                    + "必须优先调用工具查询真实业务数据，不要编造结果。"
                    + "如果提到优惠券，要先查店铺再查优惠券；"
                    + "如果提到评分、高分、最好评，要按评分从高到低返回；"
                    + "如果提到距离、附近、近、离我近，要按距离从近到远返回。";

    private final IShopTypeService shopTypeService;

    private String shopTypePrompt = "";

    @PostConstruct
    public void initShopTypePrompt() {
        List<String> shopTypes = shopTypeService.list().stream()
                .map(ShopType::getName)
                .collect(Collectors.toList());
        this.shopTypePrompt = "平台支持的店铺类型包括：" + String.join("、", shopTypes) + "。";
    }

    public String getDefaultSystemPrompt() {
        return DEFAULT_SYSTEM_PROMPT;
    }

    public String buildAgentSystemPrompt(AgentIntent intent) {
        return DEFAULT_AGENT_SYSTEM_PROMPT + shopTypePrompt + buildIntentPrompt(intent);
    }

    public String appendRagContext(String basePrompt, String ragContext) {
        if (ragContext == null || ragContext.trim().isEmpty()) {
            return basePrompt;
        }
        return basePrompt + "\n" + ragContext;
    }

    private String buildIntentPrompt(AgentIntent intent) {
        StringBuilder builder = new StringBuilder();
        if (intent.isNeedVoucher()) {
            builder.append("本轮用户关注优惠券，找到店铺后要补充优惠券信息。");
        }
        if (intent.isSortByScore()) {
            builder.append("本轮用户关注评分，搜索时按评分从高到低排序。");
        }
        if (intent.isSortByDistance()) {
            if (intent.getX() != null && intent.getY() != null) {
                builder.append("本轮用户关注距离，搜索时按距离从近到远排序。");
            } else {
                builder.append("本轮用户关注距离，但当前没有定位，回答时要说明距离能力受限。");
            }
        }
        return builder.toString();
    }
}
