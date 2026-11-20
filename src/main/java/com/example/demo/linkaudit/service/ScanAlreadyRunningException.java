package com.example.demo.linkaudit.service;

import com.example.demo.linkaudit.model.AuditJobState;

/**
 * Thrown when a link audit scan is requested while another scan job is already active.
 */
public class ScanAlreadyRunningException extends RuntimeException {

    private final AuditJobState activeJobState;

    public ScanAlreadyRunningException(AuditJobState activeJobState) {
        super("Ja hi ha una auditoria d'enllaços en curs");
        this.activeJobState = activeJobState;
    }

    public AuditJobState getActiveJobState() {
        return activeJobState;
    }
}
