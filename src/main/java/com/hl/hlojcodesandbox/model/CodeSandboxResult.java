package com.hl.hlojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 代码沙箱返回结果
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CodeSandboxResult {

    private List<String> outputList;

    private String message;

    private JudgeInfo judgeInfo;
}
