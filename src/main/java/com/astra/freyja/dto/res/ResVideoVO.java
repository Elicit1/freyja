package com.astra.freyja.dto.res;
import com.astra.freyja.entity.ResVideo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResVideoVO extends ResVideo {
    private String dramaTitle;
    private String shotName;
}
