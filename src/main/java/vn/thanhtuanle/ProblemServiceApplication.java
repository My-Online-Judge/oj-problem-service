package vn.thanhtuanle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class ProblemServiceApplication {

	public static void main(String[] args) {
		// Run the whole app in Vietnam time (GMT+7). Entities audit createdAt/updatedAt as naive
		// LocalDateTime via LocalDateTime.now(), which reads the JVM default zone — in Docker that is
		// UTC unless pinned here. Set before SpringApplication.run so auditing and logs both use it.
		// Uses the JRE's bundled tz database, so it works even on the alpine image (no OS tzdata).
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
		SpringApplication.run(ProblemServiceApplication.class, args);
	}

}
