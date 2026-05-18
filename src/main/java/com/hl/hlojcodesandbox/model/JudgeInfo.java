package com.hl.hlojcodesandbox.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class JudgeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 程序执行信息 */
    private String message;

    /** 消耗时间（ms） */
    private Long time;

    /** 消耗内存（kb） */
    private Long memory;
}
