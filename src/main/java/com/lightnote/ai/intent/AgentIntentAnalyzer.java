package com.lightnote.ai.intent;

import com.lightnote.ai.model.AgentIntent;
import com.lightnote.dto.AiAgentRequest;
import com.lightnote.dto.AiMessageDTO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentIntentAnalyzer {

    /**
     * 分析智能体意图
     *
     * @param recentMessages 最近消息列表
     * @param request        智能体请求
     * @return 分析后的智能体意图
     */
    public AgentIntent analyze(List<AiMessageDTO> recentMessages, AiAgentRequest request) {
        AiMessageDTO lastMessage = getLastMessage(recentMessages);
        String lastUserText = lastMessage == null || lastMessage.getContent() == null ? "" : lastMessage.getContent();

        boolean needVoucher = containsAny(lastUserText, "优惠", "优惠券", "代金券", "团购", "券");
        boolean sortByScore = containsAny(lastUserText, "评分", "高分", "最好评", "评价高", "分高");
        boolean sortByDistance = containsAny(lastUserText, "距离", "附近", "近", "离我", "周边");

        Double x = request == null ? null : request.getX();
        Double y = request == null ? null : request.getY();
        return new AgentIntent(needVoucher, sortByScore, sortByDistance, x, y);
    }

    /**
     * 解析排序方式
     *
     * @param sortBy 排序方式
     * @param intent 智能体意图
     * @return 解析后的排序方式
     */
    public String resolveSortBy(String sortBy, AgentIntent intent) {
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            return sortBy.trim();
        }
        if (intent.isSortByDistance() && intent.getX() != null && intent.getY() != null) {
            return "distance_asc";
        }
        if (intent.isSortByScore()) {
            return "score_desc";
        }
        return "default";
    }

    /**
     * 获取最近一条用户消息
     *
     * @param recentMessages 最近消息列表
     * @return 最近一条用户消息
     */
    private AiMessageDTO getLastMessage(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return null;
        }
        return recentMessages.get(recentMessages.size() - 1);
    }

    /**
     * 检查文本是否包含任意关键词
     *
     * @param text    要检查的文本
     * @param keywords 关键词数组
     * @return 如果文本包含任意关键词则返回true，否则返回false
     */
    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
