package com.hl.hlojcodesandbox.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/health")
public class healthController {

    @GetMapping("/")
    public String testHealth() {
        return "ok";
    }
}
