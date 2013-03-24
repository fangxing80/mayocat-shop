package org.mayocat.rest.resources;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;

import org.mayocat.rest.annotation.ExistingTenant;
import org.mayocat.rest.Resource;
import org.xwiki.component.annotation.Component;

@Component("/api/1.0/logout/")
@Path("/api/1.0/logout/")
@ExistingTenant
public class LogoutResource implements Resource
{

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response logout()
    {
        NewCookie newUserCookie = new NewCookie("username", "", "/", null, null, 0, false);
        NewCookie newPassCookie = new NewCookie("password", "", "/", null, null, 0, false);

        return Response.ok().cookie(newUserCookie, newPassCookie).build();
    }
}
