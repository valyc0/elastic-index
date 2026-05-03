package com.example.ragclient;

import com.vaadin.flow.component.page.AppShellConfigurator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@com.vaadin.flow.component.page.Push
public class RagClientApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(RagClientApplication.class, args);
    }
}
