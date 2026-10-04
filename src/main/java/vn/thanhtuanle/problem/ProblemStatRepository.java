package vn.thanhtuanle.problem;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.thanhtuanle.entity.ProblemStat;

public interface ProblemStatRepository extends JpaRepository<ProblemStat, ProblemStat.Key> {
}
