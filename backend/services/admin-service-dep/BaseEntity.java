package com.project.authservice.entity;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;
import java.util.UUID;
@MappedSuperclass
@Getter
@Setter
@EqualsAndHashCode (onlyExplicitlyIncluded = true)
@EntityListeners (AuditingEntityListener.class)
public abstract class BaseEntity
{
	@Id
	@GeneratedValue (strategy = GenerationType.IDENTITY)
	@Column (name = "id", updatable = false, nullable = false)
	private Long id;
	@EqualsAndHashCode.Include
	@Column (name = "public_id", columnDefinition = "uuid", updatable = false, nullable = false, unique = true)
	private UUID publicId;
	@CreatedDate
	@Column (name = "created_at", updatable = false, nullable = false)
	@Setter (AccessLevel.NONE)
	private LocalDateTime createdAt;
	@LastModifiedDate
	@Column (name = "updated_at", nullable = false)
	@Setter (AccessLevel.NONE)
	private LocalDateTime updatedAt;
	@PrePersist
	public void generatePublicId ()
	{
		if (publicId == null)
		{
			publicId = UuidCreator.getTimeOrderedEpoch ();
		}
	}
}