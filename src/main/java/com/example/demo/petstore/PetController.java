package com.example.demo.petstore;

import com.example.demo.petstore.model.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v3/pet")
public class PetController {
    private final PetStoreService service;
    public PetController(PetStoreService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Pet> addPet(@RequestBody Pet pet) { return ResponseEntity.ok(service.addPet(pet)); }

    @PutMapping
    public ResponseEntity<Pet> updatePet(@RequestBody Pet pet) {
        return service.updatePet(pet).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/findByStatus")
    public ResponseEntity<List<Pet>> findByStatus(@RequestParam(defaultValue = "available") String status) {
        return ResponseEntity.ok(service.findPetsByStatus(status));
    }

    @GetMapping("/findByTags")
    public ResponseEntity<List<Pet>> findByTags(@RequestParam List<String> tags) {
        return ResponseEntity.ok(service.findPetsByTags(tags));
    }

    @GetMapping("/{petId}")
    public ResponseEntity<Pet> getPetById(@PathVariable Long petId) {
        return service.getPetById(petId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{petId}")
    public ResponseEntity<Pet> updatePetWithForm(@PathVariable Long petId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String status) {
        return service.updatePetWithForm(petId, name, status).map(ResponseEntity::ok).orElse(ResponseEntity.badRequest().build());
    }

    @DeleteMapping("/{petId}")
    public ResponseEntity<Void> deletePet(@PathVariable Long petId) {
        return service.deletePet(petId) ? ResponseEntity.ok().<Void>build() : ResponseEntity.notFound().<Void>build();
    }

    @PostMapping("/{petId}/uploadImage")
    public ResponseEntity<PetApiResponse> uploadImage(@PathVariable Long petId,
            @RequestParam(required = false) String additionalMetadata) {
        return service.getPetById(petId)
            .map(p -> ResponseEntity.ok(new PetApiResponse(200, "unknown", "additionalMetadata: " + additionalMetadata)))
            .orElse(ResponseEntity.notFound().build());
    }
}