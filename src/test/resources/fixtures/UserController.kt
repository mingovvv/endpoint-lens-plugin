package fixture.api

@RequestMapping(path = ["/api"])
class UserController {
    @GetMapping("/users/{id}")
    fun getUser() {
    }

    @RequestMapping(path = ["/users"], method = [RequestMethod.POST], consumes = ["application/json"])
    fun createUser() {
    }
}

