package com.nbh.backend.order.words;

import com.nbh.backend.order.words.OrderWordDtos.OrderWordDto;
import com.nbh.backend.order.words.OrderWordDtos.OrderWordInput;
import com.nbh.backend.order.words.OrderWordDtos.Suggestion;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The word list behind the paste box (see {@link OrderWordService}): everyone who enters orders reads it, teaches it and
 * looks after it, since they are the ones who read the customers' messages.
 */
@RestController
@RequestMapping("/api/v1/admin/order-words")
@PreAuthorize("hasAnyAuthority('PERM_ORDER_ENTER','PERM_ORDER_MANAGE_ALL')")
public class OrderWordController {

    private final OrderWordService service;

    public OrderWordController(OrderWordService service) {
        this.service = service;
    }

    @GetMapping
    public List<OrderWordDto> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderWordDto add(@Valid @RequestBody OrderWordInput input, @AuthenticationPrincipal StaffUser staff) {
        return service.add(input, staff == null ? null : staff.getFullName());
    }

    @PutMapping("/{id}")
    public OrderWordDto update(@PathVariable UUID id, @Valid @RequestBody OrderWordInput input) {
        return service.update(id, input);
    }

    @PostMapping("/{id}/approve")
    public OrderWordDto approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    /** Corrections from one pasted message, each a suggested nickname (at most 60 at a time). */
    @PostMapping("/suggestions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suggest(@Valid @RequestBody List<@Valid Suggestion> suggestions, @AuthenticationPrincipal StaffUser staff) {
        for (Suggestion s : suggestions.stream().limit(60).toList()) {
            service.suggest(s, staff == null ? null : staff.getFullName());
        }
    }
}
