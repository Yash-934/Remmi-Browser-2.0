fn main() {
    let result = adblock::engine::Engine::new(true).check_network_request(&adblock::request::Request::new("http://example.com", "http://example.com", "other").unwrap());
    println!("{:?}", result);
}
