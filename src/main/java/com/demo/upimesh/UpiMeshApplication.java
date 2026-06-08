package com.demo.upimesh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/**
 * Entry point for UPI-Zero — Offline UPI Infrastructure.
 *
 * Features:
 * - Offline mesh-based transaction propagation
 * - Gossip protocol simulation
 * - Hybrid encrypted packet exchange
 *
 * Run:
 *   mvnw.cmd spring-boot:run
 *
 * Open:
 *   http://localhost:8080
 */
@SpringBootApplication
public class UpiMeshApplication {
    public static void main(String[] args) {
        SpringApplication.run(UpiMeshApplication.class, args);
    }
}
