package com.example.demo.petstore;

import com.example.demo.petstore.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v3/user")
public class UserController {
    private final PetStoreService service;
    public UserController(PetStoreService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody User user) { return ResponseEntity.ok(service.createUser(user)); }

    @PostMapping("/createWithList")
    public ResponseEntity<List<User>> createUsersWithList(@RequestBody List<User> users) {
        return ResponseEntity.ok(service.createUsersWithList(users));
    }

    @GetMapping("/login")
    public ResponseEntity<String> loginUser(@RequestParam(required = false) String username,
                                             @RequestParam(required = false) String password) {
        return service.getUserByName(username)
            .filter(u -> u.getPassword().equals(password))
            .map(u -> ResponseEntity.ok("logged in user session:" + System.currentTimeMillis()))
            .orElse(ResponseEntity.badRequest().<String>build());
    }

    @GetMapping("/logout")
    public ResponseEntity<String> logoutUser() { return ResponseEntity.ok("ok"); }

    @GetMapping("/{username}")
    public ResponseEntity<User> getUserByName(@PathVariable String username) {
        return service.getUserByName(username).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{username}")
    public ResponseEntity<Void> updateUser(@PathVariable String username, @RequestBody User user) {
        return service.updateUser(username, user) ? ResponseEntity.ok().<Void>build() : ResponseEntity.notFound().<Void>build();
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<Void> deleteUser(@PathVariable String username) {
        return service.deleteUser(username) ? ResponseEntity.ok().<Void>build() : ResponseEntity.notFound().<Void>build();
    }
}
