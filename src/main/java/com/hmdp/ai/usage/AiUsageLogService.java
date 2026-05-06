package com.hmdp.ai.usage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.config.AiProviderProperties;
import com.hmdp.config.AiFeatureProperties;
import com.hmdp.dto.Result;
import com.hmdp.entity.AiUsageLog;
import com.hmdp.mapper.AiUsageLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AiUsageLogService {

    private final AiUsageLogMapper aiUsageLogMapper;
    private final AiProviderProperties aiProviderProperties;
    private final AiFeatureProperties aiFeatureProperties;

    @Value("${ai.model:qwen-max}")
    private String modelName;

    public void record(Long userId,
                       String bizMode,
                       String promptText,
                       String completionText,
                       long latencyMs,
                       boolean success,
                       String errorMsg) {
        int promptTokens = estimateTokens(promptText);
        int completionTokens = estimateTokens(completionText);
        int totalTokens = promptTokens + completionTokens;

        double inputCost = promptTokens / 1000D * aiFeatureProperties.getCost().getInputPricePer1k();
        double outputCost = completionTokens / 1000D * aiFeatureProperties.getCost().getOutputPricePer1k();
        BigDecimal estimatedCost = BigDecimal.valueOf(inputCost + outputCost).setScale(6, RoundingMode.HALF_UP);

        AiUsageLog usageLog = new AiUsageLog()
                .setUserId(userId)
                .setProviderType(aiProviderProperties.getType())
                .setModelName(modelName)
                .setBizMode(bizMode)
                .setPromptTokens(promptTokens)
                .setCompletionTokens(completionTokens)
                .setTotalTokens(totalTokens)
                .setEstimatedCost(estimatedCost)
                .setLatencyMs(latencyMs)
                .setSuccess(success)
                .setErrorMsg(errorMsg)
                .setPromptPreview(abbreviate(promptText, 300))
                .setCreateTime(LocalDateTime.now());
        aiUsageLogMapper.insert(usageLog);
    }

    public Result recent(Long userId, int limit) {
        if (userId == null) {
            return Result.fail("用户未登录");
        }
        LambdaQueryWrapper<AiUsageLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiUsageLog::getUserId, userId)
                .orderByDesc(AiUsageLog::getCreateTime)
                .last("limit " + Math.max(1, limit));
        List<AiUsageLog> logs = aiUsageLogMapper.selectList(wrapper);
        return Result.ok(logs);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.trim().length();
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
