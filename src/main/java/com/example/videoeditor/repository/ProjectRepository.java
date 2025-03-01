package com.example.videoeditor.repository;

import com.example.videoeditor.entity.Project;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByUserOrderByLastModifiedDesc(User user);
    Project findByIdAndUser(Long id, User user);
}