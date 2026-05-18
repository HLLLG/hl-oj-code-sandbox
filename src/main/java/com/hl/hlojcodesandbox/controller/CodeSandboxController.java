package com.hl.hlojcodesandbox.controller;

import com.hl.hlojcodesandbox.CodeSandbox;
import com.hl.hlojcodesandbox.model.CodeSandboxRequest;
import com.hl.hlojcodesandbox.model.CodeSandboxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/executeCode")
public class CodeSandboxController {

    @Autowired
    private CodeSandbox codeSandbox;

    @PostMapping
    public CodeSandboxResult executeCode(@RequestBody CodeSandboxRequest request) {
        return codeSandbox.executeCode(request);
    }
}
