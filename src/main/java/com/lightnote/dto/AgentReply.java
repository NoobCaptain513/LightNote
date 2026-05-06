package com.lightnote.dto;

import lombok.Data;

import java.util.List;

// AgentReply.java
@Data
public class AgentReply {
    private String text;           // AI 文字描述
    private List<ShopCard> shops;  // 店铺卡片列表（可为空）

    @Data
    public static class ShopCard {
        private Long id;
        private String name;
        private String area;
        private String address;
        private Double score;
        private Long avgPrice;
        private String distance;
        private String openHours;
        // 优惠券信息（可为空）
        private String voucherTitle;
        private String voucherDesc;
    }
}
