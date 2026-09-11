package uz.platform.userservice.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one genuinely public endpoint in the platform.
 *
 * <p>It exists to make the security baseline visible: call it with no
 * Authorization header at all and it returns 200, while every other endpoint
 * returns 401. That contrast is the fastest way to confirm the resource server
 * is configured and running, before any token is involved.</p>
 */
@RestController
@RequestMapping("/api/public")
public class PublicController {

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of(
                "service", "user-service",
                "message", "Public endpoint. No access token was required.");
    }
}
