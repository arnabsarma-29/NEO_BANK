package com.neobank.beneficiary.repository;

import com.neobank.beneficiary.entity.IfscDirectory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IfscDirectoryRepository extends JpaRepository<IfscDirectory, Long> {

    Optional<IfscDirectory> findByIfscCodeIgnoreCase(String ifscCode);

    boolean existsByIfscCodeIgnoreCase(String ifscCode);

    @Query("SELECT i FROM IfscDirectory i WHERE " +
           "LOWER(i.ifscCode) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(i.bankName) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(i.branchName) LIKE LOWER(CONCAT('%', :query, '%'))")
    List<IfscDirectory> searchByQuery(@Param("query") String query);
}
