package io.synlabs.synvision.views.anpr;

import io.synlabs.synvision.views.common.Request;
import lombok.Getter;
import lombok.Setter;

/**
 * Created by itrs on 10/21/2019.
 */
@Getter
@Setter
public class AnprRequest implements Request {

    public Long id;
    public String lpr;

    public AnprRequest(Long id){
        this.id=id;
    }

    public AnprRequest(){

    }

    public Long getId() {
        return unmask(id);
    }
}
