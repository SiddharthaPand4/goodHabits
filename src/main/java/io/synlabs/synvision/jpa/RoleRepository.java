package io.synlabs.synvision.jpa;

import io.synlabs.synvision.entity.core.Org;
import io.synlabs.synvision.entity.core.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Role getOneByName(String name);

    List<Role> findAllByOrg(Org org);
}