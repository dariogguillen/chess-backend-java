package io.github.dariogguillen.chess;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ChessApplicationTests {

	@Test
	void contextLoads() {
	}

}
