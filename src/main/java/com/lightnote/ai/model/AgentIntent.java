package com.lightnote.ai.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentIntent {
    private boolean needVoucher;
    private boolean sortByScore;
    private boolean sortByDistance;
    private Double x;
    private Double y;
}
