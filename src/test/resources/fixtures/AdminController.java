package fixture.api;

@RequestMapping(path={"/admin"})
public class AdminController {
    @RequestMapping(path={"/users","/staff"}, method={RequestMethod.GET, RequestMethod.DELETE})
    public String manage() {
        return "ok";
    }
}

