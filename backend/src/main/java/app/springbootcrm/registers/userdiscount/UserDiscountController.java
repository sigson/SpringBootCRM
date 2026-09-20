package app.springbootcrm.registers.userdiscount;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API табличной части «Коэффициенты пользователя».
 *
 * <p>Список и создание — в контексте владельца ({@code /api/users/{userId}/discounts}),
 * что отражает 1С-семантику «ТЧ принадлежит экземпляру агрегата». Изменение и
 * удаление конкретной строки — по её id ({@code /api/user-discounts/{id}}).
 */
@RestController
public class UserDiscountController {

    private final UserDiscountService service;

    public UserDiscountController(UserDiscountService service) {
        this.service = service;
    }

    @GetMapping("/api/users/{userId}/discounts")
    public List<UserDiscountDto> listForOwner(@PathVariable UUID userId) {
        return service.listForOwner(userId);
    }

    @PostMapping("/api/users/{userId}/discounts")
    public ResponseEntity<UserDiscountDto> create(
            @PathVariable UUID userId,
            @RequestBody UserDiscountDto.MutationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, req));
    }

    @RequestMapping(value = "/api/user-discounts/{id}", method = org.springframework.web.bind.annotation.RequestMethod.GET)
    public UserDiscountDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/api/user-discounts/{id}")
    public UserDiscountDto update(@PathVariable UUID id,
                                     @RequestBody UserDiscountDto.MutationRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/api/user-discounts/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
