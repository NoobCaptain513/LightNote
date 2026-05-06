package com.hmdp.ai.intent;

import com.hmdp.ai.model.AgentIntent;
import com.hmdp.dto.AiAgentRequest;
import com.hmdp.dto.AiMessageDTO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentIntentAnalyzer {

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

    private AiMessageDTO getLastMessage(List<AiMessageDTO> recentMessages) {
        if (recentMessages == null || recentMessages.isEmpty()) {
            return null;
        }
        return recentMessages.get(recentMessages.size() - 1);
    }

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
