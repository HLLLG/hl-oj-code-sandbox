package com.hl.hlojcodesandbox;


import com.hl.hlojcodesandbox.model.CodeSandboxRequest;
import com.hl.hlojcodesandbox.model.CodeSandboxResult;

/**
 * 代码沙箱接口
 */
public interface CodeSandbox {

    CodeSandboxResult executeCode(CodeSandboxRequest request);
}
