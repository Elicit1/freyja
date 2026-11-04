package com.astra.freyja.controller;

import com.astra.freyja.auth.AuthService;
import com.astra.freyja.auth.LoginPrincipal;
import com.astra.freyja.common.BizException;
import com.astra.freyja.common.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;

    @PostMapping("/login")
    public R<AuthService.LoginResult> login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok(auth.login(input.username(), input.password(), request.getRemoteAddr()));
    }

    @GetMapping("/me")
    public R<Map<String, String>> me(@RequestAttribute(LoginPrincipal.ATTRIBUTE) LoginPrincipal principal) {
        return R.ok(Map.of("username", principal.username()));
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestAttribute(LoginPrincipal.ATTRIBUTE) LoginPrincipal principal) {
        auth.logout(principal); return R.ok();
    }

    @PostMapping("/ws-ticket")
    public R<Map<String, String>> ticket(@RequestAttribute(LoginPrincipal.ATTRIBUTE) LoginPrincipal principal, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return R.ok(Map.of("ticket", auth.createWebSocketTicket(principal)));
    }

    @PostMapping("/password")
    public R<Void> changePassword(@RequestAttribute(LoginPrincipal.ATTRIBUTE) LoginPrincipal principal,
            @RequestBody ChangePasswordRequest input) {
        auth.changePassword(principal, input.oldPassword(), input.newPassword());
        return R.ok();
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> authError(BizException error) {
        return ResponseEntity.status(error.getCode()).header("Cache-Control", "no-store").body(R.fail(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<R<Void>> unavailable() {
        return ResponseEntity.status(503).body(R.fail(503, "登录验证服务暂不可用"));
    }

    public record LoginRequest(String username, String password) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
}
