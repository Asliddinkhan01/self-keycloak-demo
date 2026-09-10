package com.example.productservice.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.productservice.domain.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllByOrderByIdAsc();
}
