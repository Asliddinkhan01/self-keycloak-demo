package com.example.productservice.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.productservice.domain.Product;
import com.example.productservice.dto.CreateProductRequest;
import com.example.productservice.dto.ProductResponse;
import com.example.productservice.service.ProductService;

import jakarta.validation.Valid;

/**
 * Four endpoints, four different protection levels. This controller is the
 * heart of the demo.
 *
 * <pre>
 * GET    /api/products/public   public         no token needed
 * GET    /api/products          authenticated  any valid token
 * POST   /api/products          ADMIN          realm role ADMIN
 * DELETE /api/products/{id}     ADMIN          realm role ADMIN
 * </pre>
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    // Constructor injection: no @Autowired needed for a single constructor.
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * PUBLIC. Allowed by permitAll() in SecurityConfig.
     * Call it with no Authorization header at all and it still returns 200.
     */
    @GetMapping("/public")
    public Map<String, Object> publicInfo() {
        return Map.of(
                "message", "This endpoint is public. No access token was required.",
                "productCount", productService.count());
    }

    /**
     * AUTHENTICATED. Any valid, unexpired, correctly signed token from the demo
     * realm works, regardless of roles. No token means 401.
     */
    @GetMapping
    public List<ProductResponse> listProducts() {
        return productService.findAll().stream().map(ProductResponse::from).toList();
    }

    /**
     * ADMIN ONLY.
     *
     * <p>hasRole('ADMIN') is evaluated as hasAuthority('ROLE_ADMIN'). That
     * authority only exists because KeycloakRealmRoleConverter read
     * realm_access.roles out of the JWT and prefixed each role with ROLE_.
     * Delete that converter and this endpoint returns 403 even for the admin
     * user.</p>
     *
     * <p>A caller with a valid token but only the USER role gets 403 Forbidden,
     * not 401: they proved who they are, they are just not allowed.</p>
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request,
                                                         Authentication authentication) {
        // authentication.getName() is the Keycloak username because
        // SecurityConfig set principalClaimName to "preferred_username".
        // It is written straight into product.created_by, which is how a token
        // claim ends up as a column value in PostgreSQL.
        Product created = productService.create(request.name(), request.price(), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(created));
    }

    /** ADMIN ONLY. Same mechanism as createProduct. */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        if (productService.delete(id)) {
            return ResponseEntity.noContent().build(); // 204
        }
        return ResponseEntity.notFound().build(); // 404
    }
}
