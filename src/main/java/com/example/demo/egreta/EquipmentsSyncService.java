package com.example.demo.egreta;

import org.springframework.stereotype.Service;

@Service
public class EquipmentsSyncService extends AbstractEgretaSyncService {
    @Override
    protected String getEndpoint() { return "equipment"; }
    @Override
    protected String getCollectionName() { return "Equipments"; }
}
