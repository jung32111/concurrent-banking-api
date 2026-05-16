package com.bank.entity;

public enum AuditAction {
    SIGNUP,
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    ACCOUNT_CREATE,
    ACCOUNT_FREEZE,
    ACCOUNT_UNFREEZE,
    ACCOUNT_ACTIVATE,
    DEPOSIT,
    WITHDRAW,
    TRANSFER
}
