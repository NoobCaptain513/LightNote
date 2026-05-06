package com.hmdp.ai.tool;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "shop")
public class ShopTypeAliasesProperties {

    private Map<String, List<String>> typeAliases;

}
