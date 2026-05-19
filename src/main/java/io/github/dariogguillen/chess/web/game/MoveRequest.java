package io.github.dariogguillen.chess.web.game;

import io.github.dariogguillen.chess.domain.Move;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/games/{id}/moves}.
 *
 * <p>The wire format uses plain strings: {@code from} / {@code to} are squares in algebraic
 * notation (lowercase, e.g. {@code "e2"}); {@code promotion} is the optional promotion piece name
 * (uppercase enum literal, e.g. {@code "QUEEN"}). The controller maps this record onto the domain
 * {@link Move}, whose compact constructor enforces the same invariants from the other side of the
 * boundary.
 *
 * <p>Jakarta validation runs before the controller method body — the regex on {@code from}/{@code
 * to} rejects garbage like {@code "i9"} or {@code "E2"} (uppercase) with a 400 / {@code
 * VALIDATION_FAILED}. {@code promotion} is nullable; when present it must be one of the four legal
 * promotion targets.
 *
 * @param from origin square in lowercase algebraic notation; non-blank, matches {@code
 *     ^[a-h][1-8]$}.
 * @param to destination square in lowercase algebraic notation; non-blank, matches {@code
 *     ^[a-h][1-8]$}.
 * @param promotion promotion piece name; nullable; when present matches {@code
 *     ^(KNIGHT|BISHOP|ROOK|QUEEN)$}.
 */
public record MoveRequest(
    @NotBlank
        @Pattern(regexp = "^[a-h][1-8]$")
        @Schema(description = "Origin square in algebraic notation, lowercase.", example = "e2")
        String from,
    @NotBlank
        @Pattern(regexp = "^[a-h][1-8]$")
        @Schema(
            description = "Destination square in algebraic notation, lowercase.",
            example = "e4")
        String to,
    @Pattern(regexp = "^(KNIGHT|BISHOP|ROOK|QUEEN)$")
        @Schema(
            description =
                "Promotion piece for pawn promotion; one of KNIGHT, BISHOP, ROOK, QUEEN. "
                    + "Null otherwise.",
            example = "QUEEN",
            nullable = true)
        String promotion) {}
