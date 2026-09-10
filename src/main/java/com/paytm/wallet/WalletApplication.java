package com.paytm.wallet;

import com.paytm.wallet.config.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WalletApplication {
    public static void main(String[] args) {
        DatabaseUrl.applyIfPresent();
        SpringApplication.run(WalletApplication.class, args);
    }
}
