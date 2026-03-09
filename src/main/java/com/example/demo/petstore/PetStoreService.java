package com.example.demo.petstore;

import com.example.demo.petstore.model.*;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class PetStoreService {
    private final Map<Long, Pet> pets = new HashMap<>();
    private final Map<Long, Order> orders = new HashMap<>();
    private final Map<String, User> users = new HashMap<>();
    private final AtomicLong petIdSeq = new AtomicLong(10);
    private final AtomicLong orderIdSeq = new AtomicLong(10);
    private final AtomicLong userIdSeq = new AtomicLong(10);

    @PostConstruct
    void init() {
        Category dogs = new Category(1L, "Dogs");
        Category cats = new Category(2L, "Cats");
        pets.put(1L, new Pet(1L, "旺财", dogs, List.of("https://example.com/dog1.jpg"), List.of(new Tag(1L, "cute")), "available"));
        pets.put(2L, new Pet(2L, "咪咪", cats, List.of("https://example.com/cat1.jpg"), List.of(new Tag(2L, "fluffy")), "available"));
        pets.put(3L, new Pet(3L, "小黑", dogs, List.of("https://example.com/dog2.jpg"), List.of(), "pending"));
        pets.put(4L, new Pet(4L, "大白", cats, List.of("https://example.com/cat2.jpg"), List.of(), "sold"));
        users.put("user1", new User(1L, "user1", "John", "Doe", "john@example.com", "pass1", "12345", 1));
    }

    public Pet addPet(Pet pet) {
        if (pet.getId() == null || pet.getId() == 0) pet.setId(petIdSeq.incrementAndGet());
        pets.put(pet.getId(), pet);
        return pet;
    }

    public Optional<Pet> updatePet(Pet pet) {
        if (pet.getId() == null || !pets.containsKey(pet.getId())) return Optional.empty();
        pets.put(pet.getId(), pet);
        return Optional.of(pet);
    }

    public Optional<Pet> getPetById(Long id) { return Optional.ofNullable(pets.get(id)); }

    public boolean deletePet(Long id) { return pets.remove(id) != null; }

    public List<Pet> findPetsByStatus(String status) {
        return pets.values().stream().filter(p -> status.equals(p.getStatus())).collect(Collectors.toList());
    }

    public List<Pet> findPetsByTags(List<String> tagNames) {
        return pets.values().stream()
            .filter(p -> p.getTags() != null && p.getTags().stream().anyMatch(t -> tagNames.contains(t.getName())))
            .collect(Collectors.toList());
    }

    public Optional<Pet> updatePetWithForm(Long id, String name, String status) {
        Pet pet = pets.get(id);
        if (pet == null) return Optional.empty();
        if (name != null) pet.setName(name);
        if (status != null) pet.setStatus(status);
        return Optional.of(pet);
    }

    public Map<String, Long> getInventory() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("available", pets.values().stream().filter(p -> "available".equals(p.getStatus())).count());
        m.put("pending",   pets.values().stream().filter(p -> "pending".equals(p.getStatus())).count());
        m.put("sold",      pets.values().stream().filter(p -> "sold".equals(p.getStatus())).count());
        return m;
    }

    public Order placeOrder(Order order) {
        if (order.getId() == null || order.getId() == 0) order.setId(orderIdSeq.incrementAndGet());
        orders.put(order.getId(), order);
        return order;
    }

    public Optional<Order> getOrderById(Long id) { return Optional.ofNullable(orders.get(id)); }
    public boolean deleteOrder(Long id) { return orders.remove(id) != null; }

    public User createUser(User user) {
        if (user.getId() == null || user.getId() == 0) user.setId(userIdSeq.incrementAndGet());
        users.put(user.getUsername(), user);
        return user;
    }

    public List<User> createUsersWithList(List<User> list) {
        return list.stream().map(this::createUser).collect(Collectors.toList());
    }

    public Optional<User> getUserByName(String username) { return Optional.ofNullable(users.get(username)); }

    public boolean updateUser(String username, User user) {
        if (!users.containsKey(username)) return false;
        user.setUsername(username);
        users.put(username, user);
        return true;
    }

    public boolean deleteUser(String username) { return users.remove(username) != null; }
}