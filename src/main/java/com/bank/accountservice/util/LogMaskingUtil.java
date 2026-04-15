package com.bank.accountservice.util;

public class LogMaskingUtil {

    private LogMaskingUtil() {}

    /**
     * 계좌번호 마스킹: 100-12345678 → 100-****5678
     */
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        int dashIdx = accountNumber.lastIndexOf('-');
        if (dashIdx != -1 && accountNumber.length() - dashIdx > 5) {
            String prefix = accountNumber.substring(0, dashIdx + 1);
            String visible = accountNumber.substring(accountNumber.length() - 4);
            return prefix + "****" + visible;
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
