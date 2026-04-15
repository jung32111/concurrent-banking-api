package com.bank.accountservice.service;

import com.bank.accountservice.entity.AuditAction;
import com.bank.accountservice.entity.AuditLog;
import com.bank.accountservice.repository.AuditLogRepository;
import com.bank.accountservice.util.LogMaskingUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * REQUIRES_NEW: 메인 트랜잭션과 독립적으로 커밋 — 금융 감사 로그는 롤백되면 안 됨
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, AuditAction action, String accountNumber, BigDecimal amount) {
        String maskedAccount = accountNumber != null ? LogMaskingUtil.maskAccountNumber(accountNumber) : null;
        String ip = resolveClientIp();

        AuditLog auditLog = AuditLog.builder()
                .userId(userId)
                .action(action)
                .accountNumber(maskedAccount)
                .amount(amount)
                .ipAddress(ip)
                .build();

        auditLogRepository.save(auditLog);
        log.info("[AUDIT] userId={}, action={}, account={}, amount={}, ip={}",
                userId, action, maskedAccount, amount, ip);
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                    return xForwardedFor.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }
}
