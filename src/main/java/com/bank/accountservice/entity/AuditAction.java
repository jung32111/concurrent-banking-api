package com.bank.accountservice.entity;

public enum AuditAction {
    SIGNUP,
    LOGIN,
    LOGOUT,
    ACCOUNT_CREATE,
    ACCOUNT_FREEZE,
    ACCOUNT_UNFREEZE,
    ACCOUNT_ACTIVATE,
    DEPOSIT,
    WITHDRAW,
    TRANSFER
}
