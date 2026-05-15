package io.github.dariogguillen.chess;

import org.springframework.boot.SpringApplication;

public class TestChessApplication {

  public static void main(String[] args) {
    SpringApplication.from(ChessApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
