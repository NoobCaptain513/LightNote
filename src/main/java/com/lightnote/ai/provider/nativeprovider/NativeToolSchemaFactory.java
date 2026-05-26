package com.lightnote.ai.provider.nativeprovider;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class NativeToolSchemaFactory {

        /**
         * 构建本地工具的JSON Schema
         * @return 包含本地工具JSON Schema的列表
         */
    public List<Map<String, Object>> buildSchemas() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "searchShop",
                        "description", "根据关键词搜索店铺，支持按评分或距离排序",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "keyword", Map.of("type", "string", "description", "搜索关键词"),
                                        "sortBy", Map.of("type", "string", "description", "可选值: default, score_desc, distance_asc"),
                                        "x", Map.of("type", "number", "description", "用户经度"),
                                        "y", Map.of("type", "number", "description", "用户纬度")
                                ),
                                "required", List.of("keyword")
                        )
                )
        ));

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "getVoucher",
                        "description", "查询指定店铺的优惠券信息",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "shopId", Map.of("type", "integer", "description", "店铺ID")
                                ),
                                "required", List.of("shopId")
                        )
                )
        ));

        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "getShopDetail",
                        "description", "查询指定店铺的详细信息",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "shopId", Map.of("type", "integer", "description", "店铺ID")
                                ),
                                "required", List.of("shopId")
                        )
                )
        ));

        return tools;
    }
}
