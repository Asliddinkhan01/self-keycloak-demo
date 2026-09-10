package com.example.productservice.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.productservice.domain.Product;
import com.example.productservice.repo.ProductRepository;

/**
 * Products, stored in the product_service schema of PostgreSQL.
 *
 * <p>Nothing about security appears in this class. Authorization is decided
 * before the request ever reaches here, by the PreAuthorize annotations on
 * ProductController. Keeping the two apart is deliberate: the service layer
 * stays about products, and every rule about who may do what sits in one
 * readable place.</p>
 */
@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Product> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }

    @Transactional
    public Product create(String name, BigDecimal price, String createdBy) {
        return repository.save(new Product(name, price, createdBy));
    }

    /** @return true if a row was removed, false if the id was unknown. */
    @Transactional
    public boolean delete(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }
}
