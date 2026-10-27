local ReplicatedStorage = game:GetService("ReplicatedStorage")

local folder = Instance.new("Folder")
folder.Name = "Remotes"
folder.Parent = ReplicatedStorage

local remotes = {}
for _, name in { "Fire", "Reload", "UsePower", "Tracer", "Hit", "Cutscene", "Shake", "Announce" } do
	local event = Instance.new("RemoteEvent")
	event.Name = name
	event.Parent = folder
	remotes[name] = event
end

return remotes
