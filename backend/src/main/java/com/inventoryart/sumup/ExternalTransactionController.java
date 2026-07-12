package com.inventoryart.sumup;

import com.inventoryart.common.PageResponse;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import static com.inventoryart.common.QueryTimeBounds.from;
import static com.inventoryart.common.QueryTimeBounds.to;

@RestController
@RequestMapping("/api/v1/external-transactions")
public class ExternalTransactionController {
    private final ExternalTransactionRepository transactions;
    private final CurrentUserService currentUser;
    private final ExternalTransactionLinkService links;

    public ExternalTransactionController(ExternalTransactionRepository transactions, CurrentUserService currentUser,
                                         ExternalTransactionLinkService links) {
        this.transactions = transactions;
        this.currentUser = currentUser;
        this.links = links;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<ExternalTransactionDtos.Response> list(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        Pageable pageable) {
        return PageResponse.of(transactions.search(currentUser.tenantId(), status, from(from), to(to), pageable)
            .map(ExternalTransactionDtos.Response::from));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ExternalTransactionDtos.Response get(@PathVariable UUID id) {
        return transactions.findByIdAndTenantId(id, currentUser.tenantId()).map(ExternalTransactionDtos.Response::from)
            .orElseThrow(() -> new NotFoundException("External transaction"));
    }

    @GetMapping("/{id}/matches")
    public List<ExternalTransactionDtos.OrderMatch> matches(@PathVariable UUID id) { return links.matches(id); }

    @PostMapping("/{id}/link-order")
    public ExternalTransactionDtos.Response link(@PathVariable UUID id,
                                                  @Valid @RequestBody ExternalTransactionDtos.LinkOrderRequest request) {
        return links.link(id, request.orderId());
    }
}
