package com.example.videoeditor.repository;

import com.example.videoeditor.entity.Video;
import com.example.videoeditor.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VideoRepository extends JpaRepository<Video, Long> {
    List<Video> findByUserEmail(String email);
}