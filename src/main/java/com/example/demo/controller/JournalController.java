package com.example.demo.controller;

import com.example.demo.model.Jcr;
import com.example.demo.service.JournalJcrService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/journals", "/journals", "/otr/api/journals"})
@CrossOrigin(origins = "*")
public class JournalController {

    private final JournalJcrService journalJcrService;

    public JournalController(JournalJcrService journalJcrService) {
        this.journalJcrService = journalJcrService;
    }

    @GetMapping("/{journalUuid}/jcr")
    public ResponseEntity<Map<String, Object>> getJcrByJournalUuid(@PathVariable String journalUuid) {
        return journalJcrService.findJcrByJournalUuid(journalUuid)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/jcr-by-issn")
    public List<Jcr> getJcrByIssn(@RequestParam String issn) {
        return journalJcrService.findJcrByIssn(issn);
    }
}