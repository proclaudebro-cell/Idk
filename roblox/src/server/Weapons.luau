local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Config = require(ReplicatedStorage.Shared.Config)
local Remotes = require(script.Parent.Remotes)
local Zombies = require(script.Parent.Zombies)

local Weapons = {}
local state = setmetatable({}, { __mode = "k" })
local rng = Random.new()

local function buildTool(name, cfg)
	local tool = Instance.new("Tool")
	tool.Name = name
	tool.CanBeDropped = false
	tool.ToolTip = name
	tool:SetAttribute("WeaponName", name)
	tool:SetAttribute("Ammo", cfg.Mag)
	tool:SetAttribute("Reloading", false)

	local handle = Instance.new("Part")
	handle.Name = "Handle"
	handle.Size = Vector3.new(0.35, 0.7, cfg.Length)
	handle.Color = cfg.Color
	handle.Material = Enum.Material.Metal
	handle.CanCollide = false
	handle.Parent = tool

	local barrel = Instance.new("Part")
	barrel.Size = Vector3.new(0.18, 0.18, 0.6)
	barrel.Color = Color3.fromRGB(25, 25, 30)
	barrel.Material = Enum.Material.Metal
	barrel.CanCollide = false
	barrel.Massless = true
	barrel.CFrame = handle.CFrame * CFrame.new(0, 0.15, -(cfg.Length / 2 + 0.3))
	local weld = Instance.new("WeldConstraint")
	weld.Part0 = handle
	weld.Part1 = barrel
	weld.Parent = barrel
	barrel.Parent = tool

	local muzzle = Instance.new("Attachment")
	muzzle.Name = "Muzzle"
	muzzle.Position = Vector3.new(0, 0, -0.3)
	muzzle.Parent = barrel

	local strip = Instance.new("Part")
	strip.Size = Vector3.new(0.38, 0.1, cfg.Length * 0.6)
	strip.Color = cfg.Tracer
	strip.Material = Enum.Material.Neon
	strip.CanCollide = false
	strip.Massless = true
	strip.CFrame = handle.CFrame * CFrame.new(0, 0.38, 0)
	local weld2 = Instance.new("WeldConstraint")
	weld2.Part0 = handle
	weld2.Part1 = strip
	weld2.Parent = strip
	strip.Parent = tool

	return tool
end

function Weapons.give(player)
	local backpack = player:FindFirstChildOfClass("Backpack")
	if not backpack then
		return
	end
	local names = {}
	for name in Config.Weapons do
		table.insert(names, name)
	end
	table.sort(names, function(a, b)
		return Config.Weapons[a].Order < Config.Weapons[b].Order
	end)
	for _, name in names do
		local cfg = Config.Weapons[name]
		local tool = buildTool(name, cfg)
		state[tool] = { ammo = cfg.Mag, last = 0, reloading = false }
		tool.Parent = backpack
	end
end

local function reload(tool, st, cfg)
	if st.reloading or st.ammo == cfg.Mag then
		return
	end
	st.reloading = true
	tool:SetAttribute("Reloading", true)
	task.delay(cfg.ReloadTime, function()
		st.ammo = cfg.Mag
		st.reloading = false
		tool:SetAttribute("Ammo", st.ammo)
		tool:SetAttribute("Reloading", false)
	end)
end

local function spread(dir, deg)
	if deg <= 0 then
		return dir
	end
	local roll = rng:NextNumber(0, math.pi * 2)
	local r = math.rad(deg) * math.sqrt(rng:NextNumber())
	local cf = CFrame.lookAt(Vector3.zero, dir)
	return (cf * CFrame.Angles(0, 0, roll) * CFrame.Angles(r, 0, 0)).LookVector
end

-- Validates that `tool` is a real weapon the player is holding.
local function resolve(player, tool)
	if typeof(tool) ~= "Instance" or not tool:IsA("Tool") then
		return
	end
	local char = player.Character
	local humanoid = char and char:FindFirstChildOfClass("Humanoid")
	if not humanoid or humanoid.Health <= 0 or tool.Parent ~= char then
		return
	end
	local st = state[tool]
	if not st then
		return
	end
	return char, st, Config.Weapons[tool.Name]
end

Remotes.Reload.OnServerEvent:Connect(function(player, tool)
	local _, st, cfg = resolve(player, tool)
	if st then
		reload(tool, st, cfg)
	end
end)

Remotes.Fire.OnServerEvent:Connect(function(player, tool, dir)
	local char, st, cfg = resolve(player, tool)
	if not st or typeof(dir) ~= "Vector3" or dir.Magnitude < 0.5 or dir.Magnitude > 1.5 then
		return
	end
	local now = os.clock()
	if st.reloading or st.ammo <= 0 or now - st.last < cfg.FireDelay * 0.9 then
		return
	end
	st.last = now
	st.ammo -= 1
	tool:SetAttribute("Ammo", st.ammo)
	if st.ammo == 0 then
		reload(tool, st, cfg)
	end

	local head = char:FindFirstChild("Head")
	if not head then
		return
	end
	dir = dir.Unit

	local chars = {}
	for _, p in Players:GetPlayers() do
		if p.Character then
			table.insert(chars, p.Character)
		end
	end
	local params = RaycastParams.new()
	params.FilterType = Enum.RaycastFilterType.Exclude
	params.FilterDescendantsInstances = chars

	local endpoints, hits = {}, {}
	local anyHit, anyHead, anyKill = false, false, false
	for _ = 1, cfg.Pellets do
		local d = spread(dir, cfg.Spread)
		local result = workspace:Raycast(head.Position, d * cfg.Range, params)
		table.insert(endpoints, result and result.Position or head.Position + d * cfg.Range)
		table.insert(hits, result ~= nil)
		if result then
			local model = result.Instance:FindFirstAncestorOfClass("Model")
			if model and Zombies.isZombie(model) then
				local isHead = result.Instance.Name == "Head"
				local killed = Zombies.damage(model, cfg.Damage * (isHead and cfg.HeadMult or 1), player)
				anyHit = true
				anyHead = anyHead or isHead
				anyKill = anyKill or killed
			end
		end
	end

	local muzzle = tool:FindFirstChild("Muzzle", true)
	Remotes.Tracer:FireAllClients(muzzle and muzzle.WorldPosition or head.Position, endpoints, hits, tool.Name)
	if anyHit then
		Remotes.Hit:FireClient(player, anyHead, anyKill)
	end
end)

return Weapons
