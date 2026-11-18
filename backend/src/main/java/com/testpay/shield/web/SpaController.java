package com.testpay.shield.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the React app's index.html for client-side routes (the built app lives in resources/static). */
@Controller
@Profile("!train")
public class SpaController {

    @GetMapping({"/", "/upload", "/payments", "/payments/**", "/disputes", "/disputes/**", "/teams", "/teams/**",
            "/portfolio", "/whatif", "/models", "/export"})
    public String index() {
        return "forward:/index.html";
    }
}
