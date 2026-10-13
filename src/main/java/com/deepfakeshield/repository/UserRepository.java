
package com.deepfakeshield.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.deepfakeshield.model.User;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
