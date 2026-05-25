package com.hl.hlojcodesandbox.controller;

import com.hl.hlojcodesandbox.CodeSandbox;
import com.hl.hlojcodesandbox.model.CodeSandboxRequest;
import com.hl.hlojcodesandbox.model.CodeSandboxResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/executeCode")
public class CodeSandboxController {

    private static final String AUTH_REQUEST_HEADER = "Authorization";

    private static final String AUTH_REQUEST_SECRET = "secretKey";

    @Autowired
    private CodeSandbox codeSandbox;

    @PostMapping
    public CodeSandboxResult executeCode(@RequestBody CodeSandboxRequest request, HttpServletRequest httpServletRequest,
                                         HttpServletResponse response) {
        String header = httpServletRequest.getHeader(AUTH_REQUEST_HEADER);
        // 1. 鉴权
        if (!AUTH_REQUEST_SECRET.equals(header)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }
        return codeSandbox.executeCode(request);
    }
}
